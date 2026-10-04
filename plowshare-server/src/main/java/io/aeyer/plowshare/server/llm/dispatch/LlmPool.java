package io.aeyer.plowshare.server.llm.dispatch;

import io.aeyer.plowshare.server.llm.accounting.CallLifecycle;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One inference host: two lanes and the transport that reaches it.
 *
 * <p>A pool is per inference resource per endpoint, because concurrency is a property of the box
 * and not of the fleet. A serial machine gets a chat lane of one; a larger host gets more; two
 * models on two machines are two pools.
 *
 * <p><b>Saturation is a timeout, not a wait.</b> Anchor's pools are {@code ExecutorService}s with
 * unbounded queues, so a submission waits indefinitely — right for a batch ingest nobody is
 * watching, wrong for an interactive recall. Here a caller waits only for its own budget and then
 * leaves, and the request it leaves behind is prevented from starting rather than merely abandoned.
 */
public final class LlmPool implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(LlmPool.class);
  private static final int SHUTDOWN_SECONDS = 30;

  /**
   * A queue budget meaning "wait for a slot for as long as it takes" — spec 2026-09-29 §5. Only the
   * swarm scheduler asks for it, for a call it has already rationed: a scheduled call must wait its
   * turn rather than meet {@link #defaultSubmitTimeout} and end its run UNAVAILABLE. Compared by
   * {@code equals}, never used as a real duration ({@code toMillis} of it is never taken on the
   * waiting path). A caller waiting under it is still released by its own abandoned flag, polled —
   * the runtime cancels by flag, never by interrupt.
   *
   * <p>Meant for {@link #stream}, whose {@code abandoned} flag is what ends the wait. {@link
   * #complete} and {@link #embed} pass a flag that never answers true, so under NO_DEADLINE there a
   * caller would wait without bound — nothing would ever release it.
   */
  public static final Duration NO_DEADLINE = Duration.ofMillis(Long.MAX_VALUE);

  /** How often a no-deadline wait asks whether its caller has gone. */
  private static final long ABANDON_POLL_MILLIS = 50;

  private final String name;
  private final List<String> models;
  private final Set<String> vision;
  private final Map<String, String> classes;
  private final Duration defaultSubmitTimeout;
  private final LlmTransport transport;
  private final ThreadPoolExecutor chatLane;
  private final ThreadPoolExecutor embeddingLane;
  private final int swarmSlots;

  /**
   * Arguments are taken as given.
   *
   * <p>A departure from Task 1's habit of validating in the compact constructor, and a deliberate
   * one: every pool is built by {@code LlmConfig} from bound properties, and that is where a null
   * {@code defaultSubmitTimeout} or a non-positive slot count has to be refused — at boot, naming
   * the offending pool and the property, so the server does not start half-configured. A second
   * guard here would report the same fault later, with less to say about it. Nothing else may
   * construct a pool without that check.
   *
   * <p><b>{@code transport} is the exception, and this javadoc used to cite a check for it that has
   * never existed.</b> Two guards each deferring to the other, with one of the pair absent, is
   * worth more than a wording fix: {@code LlmConfig} refuses a null timeout and a zero slot count
   * and has never refused a null transport. It does not need to. It <em>constructs</em> the
   * transport it passes, one line above, so nullity here is guaranteed by the call site rather than
   * by validation, and a check would be a branch no configuration can reach — which this file
   * deletes on sight elsewhere, because a branch that cannot run still tells a reader it has been
   * thought about. The guarantee is structural and lapses the moment a second construction site
   * appears.
   *
   * <p><b>The chat lane is deliberately not cleaned up if the embedding lane fails to build, which
   * is not the same case as the one {@code LlmConfig} guards one level up.</b> There the transport
   * was hoisted out of an argument list precisely so a throwing {@code LlmPool} constructor could
   * not strand an {@code OkHttpClient}; the obvious next question is why the same argument does not
   * apply between the two {@code lane(...)} calls below. Two reasons, and both have to hold.
   *
   * <p>First, no throw is reachable between them. {@code lane(...)} floors its slot count at one,
   * so {@code ThreadPoolExecutor}'s constructor cannot raise the {@code IllegalArgumentException}
   * it reserves for a non-positive maximum, and the queue and thread factory it is handed are
   * constructed inline and are never null.
   *
   * <p>Second — and this is the half that would still matter if a throw were ever introduced — a
   * stranded lane holds nothing. A {@code ThreadPoolExecutor} starts its core threads on demand
   * inside {@code execute} and never in its constructor, so a lane that has been built and never
   * submitted to has a {@code getPoolSize()} of nought: no thread, no file descriptor, no socket,
   * nothing but an object the collector takes. That is measured and recorded in {@code
   * LlmConfig.closeWhatWasBuilt}, where the same correction was made to a claim about live daemon
   * threads. A transport is the opposite case, which is why it is guarded and this is not.
   *
   * <p>So a guard here would be several lines of code that no test can reach, releasing a resource
   * that does not exist. <b>If a lane ever acquires something at construction — a metrics
   * registration, a pre-started core — the second reason lapses and this becomes a real leak.</b>
   * Recorded here rather than fixed, so that a reader does not mistake the asymmetry with {@code
   * LlmConfig} for an oversight.
   */
  public LlmPool(
      String name,
      List<String> models,
      Map<String, String> classes,
      int chatSlots,
      int embeddingSlots,
      Duration defaultSubmitTimeout,
      LlmTransport transport) {
    this(
        name,
        models,
        classes,
        chatSlots,
        embeddingSlots,
        defaultSubmitTimeout,
        transport,
        Set.of());
  }

  /**
   * The same pool, with the models on it that can be shown a picture.
   *
   * <p>An eighth parameter rather than a setter, and the shorter form above is kept for {@code
   * AgentDefinition}'s reason: a pool built in Java by a test that has no opinion about vision and
   * a pool built from a configuration that says nothing about it have to be the same pool. <b>Empty
   * means no model here sees</b>, which is what every pool in this repository declared before the
   * key existed and what every pool an operator has not edited declares now.
   *
   * @param vision the <em>wire model</em> names on this host that can be shown an image. Wire names
   *     and not specifiers: a class is a name for a routing decision and a capability is a fact
   *     about the model the decision lands on, so declaring {@code fast} here would be declaring
   *     something about whichever model that class resolves to today
   */
  public LlmPool(
      String name,
      List<String> models,
      Map<String, String> classes,
      int chatSlots,
      int embeddingSlots,
      Duration defaultSubmitTimeout,
      LlmTransport transport,
      Set<String> vision) {
    this(
        name,
        models,
        classes,
        chatSlots,
        embeddingSlots,
        defaultSubmitTimeout,
        transport,
        vision,
        0);
  }

  /**
   * The same pool, with a ceiling the swarm scheduler may admit work to.
   *
   * @param swarmSlots Swarm slots, spec 2026-09-29 §5: the ceiling the swarm scheduler admits to
   *     this pool, 0 for none. Not validated here — {@code LlmConfig} refuses anything but {@code
   *     0} or {@code 1 <= swarm < chat}, as it does for every other slot count.
   */
  public LlmPool(
      String name,
      List<String> models,
      Map<String, String> classes,
      int chatSlots,
      int embeddingSlots,
      Duration defaultSubmitTimeout,
      LlmTransport transport,
      Set<String> vision,
      int swarmSlots) {
    this.vision = Set.copyOf(vision);
    this.name = name;
    this.models = List.copyOf(models);
    this.classes = Map.copyOf(classes);
    this.defaultSubmitTimeout = defaultSubmitTimeout;
    this.transport = transport;
    this.swarmSlots = swarmSlots;
    // Static, and it has to stay static: Java 21's `this-escape` lint —
    // an error here, since the build is -Werror — fires the moment a
    // constructor calls an instance method, and the two lanes must be
    // built before the object is published or a caller could see a null
    // executor.
    this.chatLane = lane(name, Lane.CHAT, chatSlots);
    this.embeddingLane = lane(name, Lane.EMBEDDING, embeddingSlots);
  }

  private static ThreadPoolExecutor lane(String pool, Lane lane, int slots) {
    AtomicInteger counter = new AtomicInteger();
    // A floor, not validation. Zero slots would build an executor that
    // accepts work and never runs it, which presents as every call to this
    // lane timing out — so the floor turns an unreadable hang into a
    // running pool. It is not a licence to pass zero: LlmConfig refuses a
    // non-positive slot count at boot, where the operator can still be told
    // which pool and which property. Do not "fix" this by deleting either.
    int size = Math.max(1, slots);
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            size,
            size,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            runnable -> {
              Thread thread =
                  new Thread(
                      runnable, pool + "-" + lane.wireName() + "-" + counter.getAndIncrement());
              thread.setDaemon(true);
              return thread;
            });
    log.info("LLM pool '{}' {} lane: {} slot(s)", pool, lane.wireName(), size);
    return executor;
  }

  public String name() {
    return name;
  }

  public Duration defaultSubmitTimeout() {
    return defaultSubmitTimeout;
  }

  /** The swarm scheduler's ceiling on this pool; 0 when it admits none here. */
  public int swarmSlots() {
    return swarmSlots;
  }

  /**
   * The wire model this pool would use for {@code specifier}, or {@code null} if it does not serve
   * it.
   *
   * <p>A model name wins over a class of the same name — but that precedence is now a tie-break
   * nothing configured can reach, and the reasoning it used to carry has been overtaken. It said "a
   * pool that declares both has a naming collision, and answering with the more specific of the two
   * is the reading that cannot surprise anyone", which was written when {@code classes} was the
   * pool's own map and a collision was necessarily <em>within one pool</em>. With the map
   * server-wide the collision is between one pool's {@code models} and the whole server's class
   * map, and then the precedence does not settle anything: the pool listing the name answers it as
   * a model while every other pool serving the class's value answers that instead, so one specifier
   * gets two wire models and {@link LlmDispatcher#route} picks on queue depth. {@code
   * LlmConfig.requireClassesResolvable} refuses that configuration at boot — see its javadoc for
   * why refusing it rejects nothing an operator could want — so what is left here is a rule for an
   * input no boot produces, kept because {@code resolve} is called with strings from agent
   * definitions and request bodies and must be total.
   *
   * <h2>{@code models.contains(named)} is the whole of the 2026-09-07 fix</h2>
   *
   * <p>The class map is <b>server-wide</b> — every pool is handed the same {@code
   * plowshare.llm.classes} — so without that clause every pool would answer for every class, and
   * {@link LlmDispatcher#route} would hand the request to whichever was least loaded and then ask
   * it for a model it does not have. What comes back is an endpoint's 404 on a model name, one call
   * layer below the configuration that caused it.
   *
   * <p>With it, a pool answers for a class exactly when it serves the model that class names. That
   * keeps the case the key was built for — two nodes serving the <em>same</em> model both answer
   * its class and the dispatcher load-balances between them, which is what {@code application.yml}
   * meant by "adding a second is config" — and makes the case that shipped and was reverted
   * impossible: two nodes serving <em>different</em> models cannot both answer one class, because
   * the class names one model and only one of them has it.
   *
   * <p><b>It is not made redundant by the boot check.</b> {@code LlmConfig} refuses a class naming
   * a model <em>no</em> pool serves; this clause is about a class naming a model <em>this</em> pool
   * does not serve, which is the ordinary case in any fleet of more than one box and is not a
   * configuration fault at all. Deleting it turns every correct multi-pool configuration into a
   * routing lottery, which is what {@code
   * a_class_naming_a_model_one_pool_serves_routes_there_however_loaded} measures.
   */
  public String resolve(String specifier) {
    if (models.contains(specifier)) {
      return specifier;
    }
    String named = classes.get(specifier);
    return named != null && models.contains(named) ? named : null;
  }

  public OptionalInt maxContextLength(String wireModel) {
    return transport.maxContextLength(wireModel);
  }

  public Duration promptTimeout() {
    return transport.promptTimeout();
  }

  /**
   * How many tokens this pool's endpoint is loaded to accept for {@code wireModel}, or empty if
   * nobody has said.
   *
   * <p>The pool's own answer is its transport's, unchanged. It is here because the pool is the only
   * thing holding the transport, and because the question a caller actually has — "how long a
   * prompt may I send for this specifier" — is answered by {@link
   * LlmDispatcher#contextLength(String, int)} one layer up, which needs {@link #resolve} and this
   * together.
   *
   * <p><b>May open a connection, so never call it at boot.</b> {@code LmStudio} discovers on first
   * ask and caches; {@code LlmConfig} keeps that off the startup path by asking {@code
   * canDiscover()} first, and this method inherits that rule rather than restating it as a guard it
   * cannot enforce.
   *
   * <p>Empty is an ordinary answer and not a failure. A pool built over a bare {@link LlmTransport}
   * — which nothing in {@code main} does, since {@code LlmConfig} builds a provider for every pool,
   * but which most of this suite's fixtures do — gets {@code LlmTransport}'s default, and a caller
   * with no number has to decide what to do without one rather than be handed a guess.
   *
   * @param wireModel a model name this pool serves, as {@link #resolve} returned it. Passing a
   *     class name answers empty, because no endpoint answers to one
   */
  public OptionalInt contextLength(String wireModel) {
    return transport.contextLength(wireModel);
  }

  /**
   * The half of {@link #contextLength(String)} that came from configuration, for a caller that has
   * to say where its number came from.
   *
   * <p>The pool's own answer is its transport's, unchanged, exactly as {@link
   * #contextLength(String)} is. Unlike that one it opens no connection: there is nothing to
   * discover here by construction. See {@link LlmTransport#configuredContextLength} for why the
   * question is asked separately rather than as a flag on the first.
   *
   * @param wireModel a model name this pool serves, as {@link #resolve} returned it
   */
  public OptionalInt configuredContextLength(String wireModel) {
    return transport.configuredContextLength(wireModel);
  }

  /**
   * Whether this pool can be shown a picture on this model.
   *
   * <p>Asked of a <b>wire model</b> and never of a specifier, for the reason the constructor gives:
   * a class names a routing decision and a capability is a fact about the model that decision lands
   * on. {@code LlmDispatcher.requireSees} is what turns a specifier into this question, and it asks
   * it of every pool rather than of the one that happens to be idle.
   *
   * <p>False for a model this pool does not serve, which is the same answer as for one it serves
   * and cannot show a picture to. That is not a conflation: the caller is asking "can I send an
   * image here", and the two answers are both no. Which of the two it was is {@code requireSees}'
   * to say, and it has the whole fleet in hand to say it with.
   */
  public boolean sees(String wireModel) {
    return vision.contains(wireModel);
  }

  /**
   * The prompt size at which a conversation on this pool's copy of {@code wireModel} folds, where
   * an operator configured one.
   *
   * <p>The pool's own answer is its transport's, unchanged, exactly as {@link
   * #contextLength(String)} is. Unlike that one it opens no connection, because there is nothing to
   * discover — see {@link LlmTransport#compactionThreshold}.
   *
   * @param wireModel a model name this pool serves, as {@link #resolve} returned it
   */
  public OptionalInt compactionThreshold(String wireModel) {
    return transport.compactionThreshold(wireModel);
  }

  /** The in-turn fold threshold, on {@link #compactionThreshold}'s terms exactly. */
  public OptionalInt compactionNowThreshold(String wireModel) {
    return transport.compactionNowThreshold(wireModel);
  }

  /**
   * What this pool serves, for the message an unsatisfiable specifier gets.
   *
   * <p>No base URL and no key: neither helps the reader pick a specifier, and one of them must
   * never be printed.
   *
   * <p><b>The classes listed are the ones this pool answers to, not every class the server
   * declares.</b> Since the map became server-wide, {@code classes.keySet()} is the same set for
   * every pool — so an operator reading a refusal that named all of them would see three identical
   * pools and no way to tell which one could take their request. Filtered through the same {@code
   * models.contains} that {@link #resolve} applies, so this line and the routing cannot disagree.
   */
  public String describe() {
    List<String> answered =
        classes.entrySet().stream()
            .filter(entry -> models.contains(entry.getValue()))
            .map(Map.Entry::getKey)
            .toList();
    return name + "[models=" + models + ", classes=" + answered + "]";
  }

  /**
   * How many requests are waiting for a slot in {@code lane}, not counting the ones running.
   *
   * <p><b>Not what the dispatcher routes on</b> — {@link #load} is, and this javadoc used to say
   * "the dispatcher routes to the shallowest queue", which describes the rule the design explicitly
   * rejected as worse than round-robin. What depth is for is saturation: it is the "how many are
   * already waiting" that {@link LlmSaturatedException} reports.
   *
   * <p>Still worth keeping honest, and for a reason that survives the correction: {@link #load}
   * counts the queue too, so a number that overstates the backlog steers traffic away from a host
   * that is in fact free. That is why {@link #submit} dequeues a request it has shed instead of
   * leaving the corpse to be counted.
   */
  public int queueDepth(Lane lane) {
    return executor(lane).getQueue().size();
  }

  /**
   * How busy {@code lane} is as a fraction of its own capacity: everything running plus everything
   * queued, over the number of slots.
   *
   * <p><b>This and not {@link #queueDepth} is what routing compares</b>, and the difference is not
   * a refinement — depth alone cannot tell a saturated pool from an idle one. Both lanes are {@code
   * ThreadPoolExecutor}s with {@code corePoolSize == maximumPoolSize} over an unbounded queue, and
   * such an executor starts a core thread for every task until the core is full and only then
   * queues anything. So an eight-slot lane with all eight slots occupied reports a queue depth of
   * <em>zero</em> — the same zero as an idle eight-slot lane, and as an idle one-slot lane. Below
   * saturation every pool reads 0, the strict {@code <} keeps the first match, and every request in
   * the fleet goes to the pool declared first while the others sit idle: worse than the round-robin
   * the design rejected, not better.
   *
   * <p>Dividing by the slot count is the second half. Raw occupancy still misjudges hosts of
   * different sizes — three waiting on an eight-slot box is a better placement than two waiting on
   * a one-slot box, and an unnormalised comparison picks the one-slot box. That case is the reason
   * the design prefers load-aware routing at all, the reference machines being an M1 Max and an M5
   * Max.
   *
   * <p>Approximate by nature: {@code getActiveCount} walks the workers and the queue can change
   * underneath it, so two pools can be compared on readings taken a moment apart. That is tolerable
   * here and nowhere else — a stale reading misplaces one call on a pool that does serve the
   * specifier, because the specifier map this is paired with is immutable. Nothing about
   * correctness rests on it, which is why there is no lock.
   */
  public double load(Lane lane) {
    ThreadPoolExecutor executor = executor(lane);
    // getCorePoolSize and not a stored field: it is the slot count after
    // the floor in lane(...), so the two cannot drift apart. It is never
    // zero, which is what keeps this finite.
    return (double) (executor.getActiveCount() + executor.getQueue().size())
        / executor.getCorePoolSize();
  }

  public Completion complete(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      ToolChoice toolChoice,
      Duration budget) {
    return complete(
        wireModel, messages, sampling, tools, toolChoice, budget, InferenceObserver.NONE);
  }

  public Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned,
      ToolChoice toolChoice,
      Duration budget) {
    return stream(
        wireModel,
        messages,
        sampling,
        tools,
        sink,
        abandoned,
        toolChoice,
        budget,
        InferenceObserver.NONE);
  }

  /**
   * What this pool's transport can put on the wire; see {@link LlmTransport#carries()}. Declared
   * here so {@link LlmDispatcher} can ask a routing without reaching through it for the transport.
   */
  public io.aeyer.plowshare.server.llm.counting.PromptCount countChat(
      String model, ChatRequest request) {
    return transport.countChat(model, request);
  }

  public io.aeyer.plowshare.server.llm.counting.PromptCount countEmbedding(
      String model, EmbeddingRequest request) {
    return transport.countEmbedding(model, request);
  }

  public boolean automaticCounting() {
    return transport.automaticCounting();
  }

  public Set<Sampling.Parameter> carries(String model, Sampling sampling) {
    return transport.carries(model, sampling);
  }

  public Set<Sampling.Parameter> carries() {
    return transport.carries();
  }

  public Embeddings embed(String wireModel, List<String> input, Duration budget) {
    return embed(wireModel, input, budget, InferenceObserver.NONE);
  }

  public Completion complete(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      ToolChoice toolChoice,
      Duration budget,
      InferenceObserver observer) {
    return complete(wireModel, messages, sampling, tools, toolChoice, budget, observer, null);
  }

  public Completion complete(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      ToolChoice toolChoice,
      Duration budget,
      InferenceObserver observer,
      Duration timeout) {
    return submit(
        Lane.CHAT,
        budget,
        () -> false,
        observer,
        () ->
            transport.complete(
                wireModel, messages, sampling, tools, toolChoice, observer, timeout));
  }

  public Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned,
      ToolChoice toolChoice,
      Duration budget,
      InferenceObserver observer) {
    return stream(
        wireModel, messages, sampling, tools, sink, abandoned, toolChoice, budget, observer, null);
  }

  public Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned,
      ToolChoice toolChoice,
      Duration budget,
      InferenceObserver observer,
      Duration timeout) {
    return submit(
        Lane.CHAT,
        budget,
        abandoned,
        observer,
        () ->
            transport.stream(
                wireModel,
                messages,
                sampling,
                tools,
                sink,
                abandoned,
                toolChoice,
                observer,
                timeout));
  }

  public Embeddings embed(
      String wireModel, List<String> input, Duration budget, InferenceObserver observer) {
    return submit(
        Lane.EMBEDDING,
        budget,
        () -> false,
        observer,
        () -> transport.embed(wireModel, input, observer));
  }

  private ThreadPoolExecutor executor(Lane lane) {
    return lane == Lane.CHAT ? chatLane : embeddingLane;
  }

  /**
   * Wait for a slot for as long as the budget allows, then hold it for as long as the call takes.
   *
   * <p>A {@code null} budget is this pool's default; see the body.
   *
   * <p>The budget bounds <em>queueing only</em>. Once the transport has been entered, the call is
   * bounded by that transport's read timeout instead — a request that has reached the model has a
   * different question in front of it, and cutting it off mid-generation would waste the work and
   * free nothing, since the box is still busy either way. {@link LlmTransport} is required to
   * provide that bound; nothing here can.
   *
   * <p><b>Exactly one side wins the start.</b> {@code claimed} is CASed by the task before it does
   * any work and by a caller that has run out of budget before it reports saturation, so "the call
   * ran" and "the caller was told it did not" are mutually exclusive by construction rather than by
   * timing.
   *
   * <p>It is worth recording why {@code FutureTask.cancel(false)} cannot carry that decision,
   * because the opposite is the natural assumption and it is wrong: a {@code FutureTask} stays
   * {@code NEW} for the whole of its callable and leaves that state only when {@code set} runs
   * <em>after</em> the callable returns. So {@code cancel(false)} returns {@code true} for a task
   * already inside the transport — and then silently discards the result, because {@code set} on a
   * {@code CANCELLED} task is a no-op. Reporting saturation on the strength of that return value
   * would mean telling a caller the model was never reached while the box generates its answer,
   * which is precisely what {@link LlmSaturatedException} promises against; and since the obvious
   * response to saturation is to retry, it would double the load on a host that is already
   * saturated.
   */
  private <T> T submit(
      Lane lane,
      Duration budget,
      BooleanSupplier abandoned,
      InferenceObserver observer,
      Supplier<T> work) {
    // A null budget means "the pool's default". The default is a property
    // of this pool, so this is the one place that knows it; a caller that
    // passes a request's raw, unset budget must not get a NullPointerException
    // several frames deep in an executor.
    Duration effective = budget == null ? defaultSubmitTimeout : budget;
    AtomicBoolean claimed = new AtomicBoolean();
    CountDownLatch started = new CountDownLatch(1);
    FutureTask<T> task =
        new FutureTask<>(
            () -> {
              if (!claimed.compareAndSet(false, true)) {
                // The caller gave up first and has already been told this call
                // did not happen. Returning without touching the transport is
                // what makes that true. Nothing reads this result.
                return null;
              }
              observer.started();
              started.countDown();
              try {
                if (observer.enabled() && abandoned.getAsBoolean()) {
                  throw new CallerAbandonedException(name);
                }
                T result = work.get();
                observer.finished(
                    result instanceof Completion completion
                            && "content_filter".equals(completion.finishReason())
                        ? CallLifecycle.REFUSED
                        : CallLifecycle.SUCCEEDED);
                return result;
              } catch (RuntimeException | Error failure) {
                observer.finished(InferenceObserver.outcome(failure));
                throw failure;
              }
            }) {
          @Override
          protected void done() {
            if (isCancelled() && claimed.compareAndSet(false, true)) {
              observer.finished(CallLifecycle.NOT_DISPATCHED);
              started.countDown();
            }
          }
        };
    observer.queued();

    try {
      executor(lane).execute(task);
    } catch (RejectedExecutionException e) {
      observer.finished(CallLifecycle.NOT_DISPATCHED);
      throw new LlmException("pool '" + name + "' is shut down and cannot take work", e);
    }

    try {
      // The latch is the wait; the CAS inside shed(...) is the decision.
      if (NO_DEADLINE.equals(effective)) {
        // Rationed upstream, so no deadline — but still the caller's to abandon. Asked
        // on a short poll because nothing interrupts a run's thread; a shed that loses
        // the race to the task means the call has started, and is then waited out below.
        while (!started.await(ABANDON_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
          if (abandoned.getAsBoolean() && shed(lane, claimed, task, observer)) {
            throw new CallerAbandonedException(name);
          }
        }
      } else if (!started.await(effective.toMillis(), TimeUnit.MILLISECONDS)
          && shed(lane, claimed, task, observer)) {
        throw new LlmSaturatedException(name, lane, effective, queueDepth(lane));
      }
      return task.get();
    } catch (InterruptedException e) {
      // An interrupted caller sheds on the same terms as one that ran out
      // of budget: if the work has not started it never will, and if it
      // has, it is left to finish. Not cancel(true) — interrupting the
      // lane thread mid-call would not reclaim the slot, because a
      // synchronous OkHttp execute() does not unblock on interrupt, so it
      // would only abandon a request that still lands on the box. That is
      // the case the paragraph above rules out, and an interrupt is not a
      // reason to make an exception to it.
      shed(lane, claimed, task, observer);
      Thread.currentThread().interrupt();
      throw new LlmException("interrupted waiting on pool '" + name + "'", e);
    } catch (java.util.concurrent.CancellationException cancelled) {
      throw new LlmException("pool '" + name + "' stopped before dispatch", cancelled);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) {
        // Rethrown as itself. Callers catch LlmException; an
        // ExecutionException wrapper would slip past every one of them
        // and surface as a 500 with the real reason two frames down.
        throw runtime;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new LlmException("pool '" + name + "' failed the call", cause);
    }
  }

  /**
   * Give up on a queued request.
   *
   * @return true if this caller got there before the task did, in which case the work is guaranteed
   *     not to run and the caller may say so
   */
  private boolean shed(
      Lane lane, AtomicBoolean claimed, FutureTask<?> task, InferenceObserver observer) {
    if (!claimed.compareAndSet(false, true)) {
      // The task claimed the start first: it is in the transport now, and
      // the caller has to wait for it rather than report anything.
      return false;
    }
    // Cleanup, not the decision — see submit's javadoc for why cancel cannot
    // be the decision. Given the claim above has already guaranteed the work
    // will not run, this line is belt-and-braces, and no test distinguishes
    // it from its own absence: deleting it leaves the suite green, which is
    // recorded here so the next reader does not take that for a missing
    // test. What it buys is that a task still sitting in the queue is never
    // entered at all, and that FutureTask drops its reference to the
    // captured payload now rather than whenever a worker reaches it. Remove
    // it knowingly or not at all.
    task.cancel(false);
    // And cancel does not dequeue: only remove/purge do. Without this the
    // cancelled task sits in the queue holding its captured lambda — and so
    // the whole prompt or embedding batch — until a worker happens to
    // dequeue it. Two hundred timed-out Tomcat workers behind a one-slot
    // lane would leave two hundred full payloads queued for as long as the
    // lane takes to chew through them, on a pool whose own javadoc faults
    // Anchor for unbounded queues. It also keeps queueDepth honest — and so
    // load(), which counts the queue and is what the dispatcher actually
    // routes on.
    executor(lane).remove(task);
    observer.finished(CallLifecycle.NOT_DISPATCHED);
    return true;
  }

  /**
   * Drains both lanes, then releases the transport.
   *
   * <p>That order and not the reverse: a lane still running holds a call inside the transport, and
   * closing an HTTP client out from under it would fail an in-flight request rather than let it
   * finish.
   *
   * <p>Deliberately not a try/finally, because neither {@link #drain} can throw: {@code shutdown}
   * and {@code shutdownNow} raise only {@code SecurityException}, which Java 21 has no manager to
   * produce, and the one {@code InterruptedException} is caught there. <b>If a step that can throw
   * is ever added to {@code drain}, {@code transport.close()} has to move into a finally block</b>
   * — skipping it leaks the OkHttp connection pool and the threads its dispatcher runs, once per
   * shutdown, with nothing left running to report it. That is the failure {@code
   * a_closed_pool_closes_its_transport} exists to catch.
   *
   * <p>Calling this twice is harmless, but the property is inherited rather than enforced: {@code
   * shutdown} is idempotent, {@code awaitTermination} returns at once on a terminated executor, and
   * {@link LlmTransport#close} is required to be idempotent. No flag guards it here, because the
   * only thing a second call can cost is a second 30s wait on a lane that already failed to drain —
   * a slow shutdown, not a leak.
   */
  @Override
  public void close() {
    drain(Lane.CHAT);
    drain(Lane.EMBEDDING);
    transport.close();
  }

  private void drain(Lane lane) {
    ThreadPoolExecutor executor = executor(lane);
    executor.shutdown();
    try {
      if (!executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
        int dropped = discard(executor.shutdownNow());
        log.warn(
            "LLM pool '{}' {} lane did not drain in {}s; {} task(s) dropped",
            name,
            lane.wireName(),
            SHUTDOWN_SECONDS,
            dropped);
      }
    } catch (InterruptedException e) {
      discard(executor.shutdownNow());
      Thread.currentThread().interrupt();
    }
  }

  private static int discard(List<Runnable> tasks) {
    for (Runnable task : tasks) {
      if (task instanceof java.util.concurrent.Future<?> future) {
        future.cancel(false);
      }
    }
    return tasks.size();
  }

  @Override
  public String toString() {
    // The name exactly as the operator wrote it. describe() and every
    // exception message render it verbatim, and a fourth spelling in the
    // logs is one more thing to fail to grep for.
    return "LlmPool(" + name + ")";
  }
}
