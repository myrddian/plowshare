package io.aeyer.plowshare.server.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.accounting.PricingCatalog;
import io.aeyer.plowshare.server.llm.dispatch.InferenceAccounting;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.TokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.UnknownSpecifierException;
import io.aeyer.plowshare.server.llm.lmstudio.LmStudio;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Turns the declared pools into the one dispatcher, and refuses to start on a configuration that
 * would fail silently later.
 *
 * <p><b>A pool cannot be overridden key by key, which is what makes the checks below load-bearing
 * rather than tidy.</b> Measured on this branch and reproduced independently: a property source of
 * higher precedence than {@code application.yml} that names <em>any</em> key inside {@code
 * pools[0]} <em>replaces the whole list entry</em> instead of merging with it. Overriding only
 * {@code pools[0].base-url} leaves {@code name=null} and {@code models=[]}, and reports no binding
 * error at all. {@link LlmProperties}'s {@code ignoreUnknownFields = false} does not save you
 * either: in a variant restating name, base URL and models, a map the override did not restate was
 * discarded in silence. So the supported operator override is the {@code ${LLM_BASE_URL:...}}
 * placeholder the YAML already reads, and these checks are the only thing standing between a
 * half-erased pool and a running server.
 *
 * <p><b>The map that measurement was taken against was {@code classes}, which has since moved off
 * the pool</b> — it is {@code plowshare.llm.classes}, top level, since 2026-09-07. The finding is
 * unchanged and the exposure is smaller: it is a fact about <em>list entries</em>, and a top-level
 * map is not one, so the class map an operator writes can no longer be erased by an override of a
 * neighbouring pool key. Every map still on a pool — {@code context-lengths}, {@code
 * compaction-thresholds} — is a list entry and still can be, which is why the paragraph stays
 * rather than being deleted along with the key it named.
 *
 * <p><b>Why boot is the place.</b> An embedding model no pool declares fails at the first write —
 * where {@code Archive.embed} deliberately <em>swallows</em> the failure so that a memory written
 * while the endpoint is down is still stored. The running server would then accept every write,
 * embed none of them, recall nothing, and report nothing, and there is no layer above it that could
 * notice. Checking here costs one map lookup.
 *
 * <p><b>No key, ever.</b> This class reads every pool's {@code api-key} to build its transport, so
 * it is exactly the place a refusal would be tempted to quote what it was given. Nothing thrown or
 * logged here contains one: a pool's <em>name</em> is what a message names.
 *
 * <p><b>That claim was false for one release, in the one way nobody was looking for, and it is
 * worth recording how.</b> The summary below logs {@code declared} — the bound {@code
 * PoolProperties} list — at INFO, which {@code application.yml} enables. {@code
 * PoolProperties.toString} allowlists two fields, name and base URL, and deliberately omits {@code
 * apiKey}; the leak was that a <em>base URL can itself carry a credential</em>, since {@code
 * https://user:sk-...@host/v1} is legal to configure. So an absolute claim about keys was being
 * made three lines above a log line that printed one, and every guard involved was individually
 * correct. The redaction now lives in {@code PoolProperties.withoutUserInfo}, at the single point
 * that renders a base URL, rather than at each site that prints one — an allowlist of fields cannot
 * catch a secret hiding inside an allowed field.
 *
 * <p>The two halves are pinned by two different tests, and citing only the first for both was a
 * real gap rather than a wording slip. {@code no_refusal_ever_prints_a_key} covers what is
 * <em>thrown</em> — and covers only that, because it fails the boot at {@code requireServed}, which
 * is before the summary below ever runs. Measured: interpolating {@code getApiKey()} into that line
 * left the whole suite green. What covers the <em>logged</em> half is {@code
 * a_key_never_reaches_the_boot_log}, which boots a valid pool carrying a key and reads the captured
 * events back.
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig implements EnvironmentAware {
  private Environment environment;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  /** Spring's list override handling otherwise treats any nested key as a known list entry. */
  private void validateNestedProperties(int index) {
    if (environment == null) return; // Direct Java fixtures have no property sources.
    var binder = Binder.get(environment);
    String prefix = "plowshare.llm.pools[" + index + "].";
    try {
      binder.bind(
          prefix + "counting",
          Bindable.of(CountingProperties.class),
          new NoUnboundElementsBindHandler(BindHandler.DEFAULT));
      binder.bind(
          prefix + "default-capabilities",
          Bindable.of(RequestCapabilities.class),
          new NoUnboundElementsBindHandler(BindHandler.DEFAULT));
      binder.bind(
          prefix + "request-capabilities",
          Bindable.mapOf(String.class, RequestCapabilities.class),
          new NoUnboundElementsBindHandler(BindHandler.DEFAULT));
    } catch (BindException invalid) {
      // BindException can include raw configured values; keep URLs and credentials out of
      // diagnostics.
      throw new IllegalStateException(
          prefix + "counting or request capabilities contain an unknown or invalid property");
    }
  }

  private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

  /**
   * The one tokenizer every surface on this server counts with.
   *
   * <p>Built eagerly at startup so that an unrecognised {@code implementation} stops the boot,
   * where an operator sees it, rather than throwing on the first read that needed a count.
   */
  @Bean
  Tokenizer tokenizer(LlmProperties properties) {
    Tokenizer built = properties.getTokenizer().build();
    log.info("counting tokens with: {}", built.describe());
    return built;
  }

  /**
   * {@code destroyMethod} drains the lanes on shutdown: orchestration work stops submitting before
   * in-flight model calls are cut off.
   *
   * <p><b>Everything that can be refused without building anything is refused first</b>, and that
   * ordering is a departure from the plan's draft, which validated each pool immediately before
   * constructing it. The two report the same fault; this one constructs no {@code OkHttpClient} and
   * no executor for a configuration already known to be bad, and narrows the window in which a
   * half-built fleet has to be cleaned up to the two steps that genuinely need pools to exist.
   */
  /**
   * What an agent's declared sampling intent means on each model this server serves.
   *
   * <h2>Here and not in {@code AgentsConfig}, although an agent is what asks</h2>
   *
   * <p>A profile is a fact about a <em>model</em>: which numbers a vendor recommends, which mode it
   * is being run in, whether it has a {@code reasoning_effort} knob at all. That is this package's
   * subject, and it is where the property naming the directory already lives. {@code AgentsConfig}
   * takes the finished object and layers it under each definition, which is the half that belongs
   * to agents.
   *
   * <p>A bean and not a static, so that a directory that exists and is wrong stops the boot naming
   * the file — the asymmetry every read configuration in this server applies: absent is fine,
   * present and wrong is not.
   */
  @Bean
  public SamplingProfiles samplingProfiles(LlmProperties props) {
    Path dir = Path.of(props.getSamplingDirectory()).toAbsolutePath();
    boolean own = Files.isDirectory(dir);
    SamplingProfiles profiles = SamplingProfiles.read(own ? dir : null);
    // NAMED IN THE BOOT LOG, which the design asks for by name. What a model
    // is sampled at is otherwise a fact with no reader: it is not on GET
    // /v1/agents, it is not in a request anybody sees, and the endpoint does
    // not echo it back.
    log.info(
        "sampling profiles loaded: {} ({})",
        profiles.names(),
        own
            ? dir + " layered over the shipped set"
            : "the shipped set alone; no directory at " + dir);
    return profiles;
  }

  @Bean(destroyMethod = "close")
  public LlmDispatcher llmDispatcher(
      LlmProperties props,
      ObjectMapper mapper,
      ObjectProvider<TokenLedger> ledger,
      ObjectProvider<InferenceAccounting> accounting) {
    return buildDispatcher(
        props,
        mapper,
        ledger.getIfAvailable(),
        accounting.getIfAvailable(() -> InferenceAccounting.NONE));
  }

  /** Compatibility for fixtures that explicitly supply the old success-only ledger. */
  public LlmDispatcher llmDispatcher(LlmProperties props, ObjectMapper mapper, TokenLedger ledger) {
    return buildDispatcher(props, mapper, ledger, InferenceAccounting.NONE);
  }

  private LlmDispatcher buildDispatcher(
      LlmProperties props,
      ObjectMapper mapper,
      TokenLedger ledger,
      InferenceAccounting accounting) {

    List<PoolProperties> declared = props.getPools();
    if (declared.isEmpty()) {
      throw new IllegalStateException(
          "plowshare.llm.pools is empty: the server has no endpoint to call and every"
              + " memory it stores would be written without an embedding."
              + " Declare one pool with a name, a base-url and at least one model —"
              + " application.yml ships that shape, and LLM_BASE_URL retargets it");
    }
    Set<String> names = new LinkedHashSet<>();
    for (int index = 0; index < declared.size(); index++) {
      PoolProperties pool = declared.get(index);
      validateNestedProperties(index);
      try {
        validate(pool, names, index);
      } catch (NullPointerException nullValue) {
        // Not defensive padding. PoolProperties' collection getters copy
        // through List.copyOf and Map.copyOf, both of which reject a null
        // element outright — so `classes: {fast: ~}` or a null entry in
        // `models` throws here, inside the one method whose whole job is
        // to name the offending pool and property, and would otherwise
        // surface as a binder stack trace carrying neither.
        throw new IllegalStateException(
            "plowshare.llm.pools["
                + index
                + "] (name '"
                + pool.getName()
                + "') has a"
                + " key written with no value: a null entry in models, or a value"
                + " under the retired per-pool classes key, as `fast: ~` binds."
                + " Give the key a value or remove the key",
            nullValue);
      }
    }
    requirePromptTimeouts(props);
    requireEmbeddingDim(props);
    requireEmbeddingMaxInputTokens(props);
    requireDefaultContextLength(props);
    requireEmbeddingModelNamed(props);
    requireClassesResolvable(props, declared);
    requireSystemResolvable(props, declared);

    // Validate prices before creating HTTP clients or pool executors. Admission will use
    // these immutable snapshots before queueing when accounting is enabled.
    PricingCatalog.from(props);
    props.getAccounting().validate();
    if (props.getAccounting().isEnabled() && accounting == InferenceAccounting.NONE) {
      throw new IllegalStateException(
          "plowshare.llm.accounting.enabled requires the durable accounting configuration");
    }

    List<LlmPool> pools = new ArrayList<>();
    try {
      for (PoolProperties pool : declared) {
        // Hoisted out of the argument list, which is where the plan's
        // draft built it. An argument is evaluated before the
        // constructor it is passed to, so a transport built there and a
        // pool constructor that then throws leaves the transport with no
        // owner and nothing to close it — the one case closeWhatWasBuilt
        // cannot reach, since the pool it would have closed was never
        // added. That is exactly the leak that method's javadoc argues
        // the cleanup exists for, so leaving this gap open would have
        // made the argument false about its own code.
        LlmProvider provider = provider(pool, mapper);
        try {
          pools.add(
              new LlmPool(
                  pool.getName(),
                  pool.getModels(),
                  // The SAME map for every pool, which is the move.
                  // A pool answers for a class only when it serves the
                  // model that class names — LlmPool.resolve checks —
                  // so handing every pool the whole map costs nothing
                  // and is what makes a class mean one thing.
                  props.getClasses(),
                  pool.getChat(),
                  pool.getEmbedding(),
                  pool.getSubmitTimeout(),
                  provider,
                  // Per pool and not shared, unlike classes above: a
                  // class is a name for a routing decision and is the
                  // same question everywhere, while whether a host
                  // serves a model in a build that exposes its vision
                  // tower is a fact about the host.
                  Set.copyOf(pool.getVision()),
                  pool.getSwarm()));
        } catch (RuntimeException poolFailed) {
          try {
            provider.close();
          } catch (RuntimeException whileClosing) {
            poolFailed.addSuppressed(whileClosing);
          }
          throw poolFailed;
        }
        // After the pool owns it, and that ordering is the same argument
        // the hoist above makes. Nothing in this call can throw today —
        // past canDiscover() a provider is reading a map — but it is a
        // call on an object holding an OkHttpClient that, until the line
        // above ran, nothing else could close. Placed before the add,
        // one throw here would strand exactly the client this block was
        // restructured to protect.
        warnAboutUnknownContextLengths(pool, provider, props);
      }
      // props::systemSpecifier and not the two-argument constructor: the
      // latter answers every `system` and `system.<type>` specifier as
      // unbound, which would make plowshare.llm.system unreachable from
      // every agent that names it, silently, in production, however it is
      // configured. See LlmDispatcher's own javadoc on the three-argument
      // constructor for why a function and not this LlmProperties itself
      // crosses into the dispatch package.
      LlmDispatcher dispatcher =
          new LlmDispatcher(
              pools,
              ledger,
              props::systemSpecifier,
              accounting,
              props.getPromptTimeout(),
              props.getFoldTimeout());
      requireEmbeddingModelIsServed(dispatcher, props);
      log.info(
          "LLM dispatcher: {} pool(s) {}, embedding model '{}' at {} dimensions",
          pools.size(),
          declared,
          props.getEmbeddingModel(),
          props.getEmbeddingDim());
      return dispatcher;
    } catch (RuntimeException failure) {
      closeWhatWasBuilt(pools, failure);
      throw failure;
    }
  }

  private static void requirePromptTimeouts(LlmProperties props) {
    requirePromptTimeout("prompt-timeout", props.getPromptTimeout());
    requirePromptTimeout("fold-timeout", props.getFoldTimeout());
    if (props.getFoldTimeout() == null) {
      return;
    }
    for (PoolProperties pool : props.getPools()) {
      Duration general =
          props.getPromptTimeout() == null ? pool.getMaxStreamDuration() : props.getPromptTimeout();
      if (props.getFoldTimeout().compareTo(general) < 0) {
        log.warn(
            "FOLD TIMEOUT CONFIGURATION WARNING: pool '{}' has fold-timeout {} BELOW"
                + " the general prompt timeout {}. The smaller fold value is ignored."
                + " Folds will use {}. Set fold-timeout at least as high as prompt-timeout.",
            pool.getName(),
            props.getFoldTimeout(),
            general,
            general);
      }
    }
  }

  private static void requirePromptTimeout(String key, Duration value) {
    if (value != null
        && (value.compareTo(Duration.ofMillis(1)) < 0
            || value.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0)) {
      throw new IllegalStateException(
          "plowshare.llm."
              + key
              + " must be positive and no more than "
              + Integer.MAX_VALUE
              + "ms; got "
              + value);
    }
  }

  /**
   * The pool's transport, wrapped in whatever its declared backend can answer beyond {@code /v1}.
   *
   * <p>A total switch with no default branch: {@code provider} is an enum, so a value outside it
   * never reaches this method — the binder refuses it — and a default here would be a branch no
   * configuration can produce, which this file deletes on sight elsewhere because it reads as
   * coverage.
   *
   * <p><b>Nothing built here opens a connection.</b> Both constructors build an {@code
   * OkHttpClient} and stop; discovery happens the first time somebody asks for a context length,
   * which is not at boot.
   */
  private static LlmProvider provider(PoolProperties pool, ObjectMapper mapper) {
    OpenAiCompatible base = new OpenAiCompatible(pool, mapper);
    return switch (pool.getProvider()) {
      case OPENAI -> base;
      case LMSTUDIO ->
          new LmStudio(
              base,
              pool.isPrefillProgress()
                  ? new io.aeyer.plowshare.server.llm.lmstudio.LmStudioSocket(
                      pool, mapper, base, base)
                  : null);
    };
  }

  /**
   * Names, at boot, every model this pool serves at a context length nobody configured and nobody
   * can discover.
   *
   * <p><b>A warning and not a refusal</b>, deliberately. The rest of this class refuses
   * configurations whose only other report would arrive somewhere that cannot name the property —
   * but a missing context length is not one of those. It is wanted by one caller, compaction, and
   * only once a conversation has run long enough to need it; refusing the boot for it would take a
   * server that serves every other request perfectly well and stop it.
   *
   * <p><b>{@link LlmProvider#canDiscover()} is asked first, and it is what makes the boot safe
   * rather than merely tidy.</b> {@link LlmProvider#contextLength(String)} may open a connection,
   * and discovery is not allowed to do that at startup: {@code application.yml} records that a
   * model name the server does not know "fails at the call rather than at startup", and a boot that
   * probed would refuse to start whenever the inference box came up second. The early return is
   * what keeps a probing provider out of this method entirely — past it, {@code canDiscover()} is
   * false, which by the interface's own contract means there is nowhere to look, so the {@code
   * contextLength} calls below can only read what was configured.
   *
   * <p>The consequence, stated so it is not read as a gap: this reports what is <em>unknowable</em>
   * and never what is merely not yet known. A pool that can discover is silent here even when its
   * node is unreachable, and the warning for that case belongs at the moment the probe fails —
   * which is where {@code LmStudio} puts it.
   *
   * <p><b>And why this is not where "which tier answered" is reported.</b> That line names one of
   * three — a configured entry, the node's own answer, or {@code
   * plowshare.llm.default-context-length} — and the middle one cannot be known here at all, for the
   * reason above: asking would mean probing on the boot path. So the tier is named where it is
   * settled, by {@code LlmDispatcher.contextLength}, once per specifier. What boot can say is what
   * this method says: these models will reach the bottom tier, and here is the number they will
   * get.
   *
   * <p>One line per pool rather than one per model, naming the pool and every model it cannot
   * answer for. A pool declaring several models would otherwise produce a paragraph of
   * near-identical warnings at every boot, which is how a warning gets filtered out.
   *
   * <p><b>Classes are not walked at all, and this paragraph used to say they were.</b> It described
   * a resolution step — classes turned into the wire models behind them so that a warning never
   * names {@code fast}, which is a key no endpoint answers to and which cannot appear in {@code
   * context-lengths} — and the method stopped performing it when the map moved to {@code
   * plowshare.llm.classes}. What holds now is the same guarantee arrived at for nothing: a class
   * resolves only through a model the pool already lists, so the pool's own {@code models} is
   * already the union that union used to compute. The property survives the move; the step does
   * not, and a reader looking for it below would not find it.
   */
  private static void warnAboutUnknownContextLengths(
      PoolProperties pool, LlmProvider provider, LlmProperties props) {
    if (provider.canDiscover()) {
      return;
    }
    Set<String> unknown = new LinkedHashSet<>();
    // The pool's models, and nothing else: this used to union in the pool's
    // own classes' values, and no longer needs to. The javadoc's last
    // paragraph is where that is argued, because this line is the absence of
    // the step and an absence is not where a reader looks for it.
    Set<String> served = new LinkedHashSet<>(pool.getModels());
    for (String model : served) {
      if (provider.contextLength(model).isEmpty()) {
        unknown.add(model);
      }
    }
    if (unknown.isEmpty()) {
      return;
    }
    log.warn(
        "pool '{}' serves {} at a context length nobody configured and this provider"
            + " cannot discover: it speaks /v1, which has no field for one."
            + " Compaction will fold these against"
            + " plowshare.llm.default-context-length, {} tokens, which is a guess"
            + " about a box nobody asked -- a model loaded at less than that has"
            + " prompts refused before a fold takes them. Set"
            + " plowshare.llm.pools[...].context-lengths[<model>], or declare the"
            + " pool's provider if its backend publishes the figure",
        pool.getName(),
        unknown,
        props.getDefaultContextLength());
  }

  /**
   * The archive's embedding capability, wired here rather than stereotyped.
   *
   * <p>Task 7 measured the alternative: this package is component scanned, so {@code @Service} on
   * {@link DispatchingEmbeddingClient} makes it an eager singleton that needs an {@link
   * LlmDispatcher} bean, and all four {@code TransactionBoundaryTest} tests failed at context
   * startup with {@code NoSuchBeanDefinitionException} until this class existed. Beside the
   * dispatcher is also the honest place for it: the two are built from the same properties and have
   * to be refused together at boot if those properties are wrong.
   */
  @Bean
  public DispatchingEmbeddingClient dispatchingEmbeddingClient(
      LlmDispatcher dispatcher, LlmProperties props, Tokenizer tokenizer) {
    return new DispatchingEmbeddingClient(dispatcher, props, tokenizer);
  }

  /**
   * Releases the pools built before a failure, and never replaces the failure with a complaint
   * about the cleanup.
   *
   * <p><b>The plan's draft justified this as "every pool built before the failure already has live
   * daemon threads", and that is not true.</b> Measured: a {@code ThreadPoolExecutor} starts its
   * core threads on demand inside {@code execute}, never in its constructor, and nothing has been
   * submitted to a pool that is still being built — so a freshly constructed {@link LlmPool} has a
   * {@code getPoolSize()} of zero and no thread bearing its lane's name exists. The same goes for
   * the OkHttp clients underneath it, whose dispatcher executor is lazy too.
   *
   * <p>The cleanup stays, for the reason that survives the correction: these are {@link
   * AutoCloseable}s this method constructed and is about to drop on the floor, and its correctness
   * must not rest on a library's current laziness. A transport constructor that ever starts
   * something of its own — a health-check pinger is the obvious one — would be released by this and
   * by nothing else, and a boot that leaked one would leak it on a path that, by definition, has no
   * running server left to report it.
   *
   * <p>Each close is guarded separately, on {@code LlmDispatcher.close}'s reasoning: one bad pool
   * must not strand every pool after it. A close that failed is attached as suppressed rather than
   * thrown, because the configuration fault is what the operator needs to read and a shutdown
   * complaint raised in its place would bury it.
   */
  private static void closeWhatWasBuilt(List<LlmPool> pools, RuntimeException failure) {
    for (LlmPool pool : pools) {
      try {
        pool.close();
      } catch (RuntimeException whileClosing) {
        failure.addSuppressed(whileClosing);
      }
    }
  }

  private static void validate(PoolProperties pool, Set<String> names, int index) {
    if (pool.getName() == null || pool.getName().isBlank()) {
      throw new IllegalStateException(
          "plowshare.llm.pools["
              + index
              + "] has no name; every entry needs one,"
              + " because it is what a saturation message and a 401 name instead of"
              + " a URL. The index is given because this is the one refusal that"
              + " cannot name the pool, and with three declared an operator would"
              + " otherwise be guessing which");
    }
    if (!names.add(pool.getName())) {
      throw new IllegalStateException(
          "two pools are both named '"
              + pool.getName()
              + "' in plowshare.llm.pools;"
              + " a saturation message could then not say which box is busy");
    }
    pool.getDefaultCapabilities().validate();
    pool.getRequestCapabilities()
        .forEach(
            (family, capability) -> {
              if (!pool.getModelFamilies().containsValue(family))
                throw new IllegalArgumentException(
                    "request capabilities must name a declared model family");
              capability.validate();
            });
    if (pool.getRequestStyle() == PoolProperties.RequestStyle.CLOUD
        && (pool.getProvider() != PoolProperties.Provider.OPENAI
            || !pool.getChatTemplateKwargs().isEmpty()
            || pool.getCounting().getUrl() != null))
      throw new IllegalArgumentException(
          "cloud pools use OpenAI transport without local template or tokenization extensions");
    if (pool.getChatTemplateKwargs().size() > 32
        || pool.getChatTemplateKwargs().entrySet().stream()
            .anyMatch(
                e ->
                    !e.getKey().matches("[A-Za-z0-9_]{1,64}")
                        || !(e.getValue() instanceof String s && s.length() <= 1024
                            || e.getValue() instanceof Boolean
                            || e.getValue() instanceof Number n
                                && Double.isFinite(n.doubleValue()))))
      throw new IllegalArgumentException("invalid pool chat-template-kwargs");
    if (pool.getBaseUrl() != null) pool.getCounting().validate(pool.getBaseUrl());
    if (pool.getBaseUrl() == null || pool.getBaseUrl().isBlank()) {
      throw new IllegalStateException(
          "pool '" + pool.getName() + "' has no base-url; there is nothing to call");
    }
    if (HttpUrl.parse(pool.getBaseUrl()) == null) {
      // The incident this whole slice is written around, caught at the one
      // place it can still be cheap. `LLM_BASE_URL=localhost:1234/v1`, one
      // missing http://, parses as nothing — and until this check it
      // *booted*, because OpenAiTransport parses lazily in url(String). The
      // first embed then failed, and Archive.embed swallows that failure by
      // design so the memory survives, so the server accepted every write,
      // embedded none, recalled nothing and reported nothing: the exact
      // outcome this class's javadoc says the boot exists to prevent.
      // TransactionBoundaryTest.a_base_url_with_no_scheme_keeps_the_memory_
      // and_answers_the_caller_normally pins the runtime half, and it stays:
      // PoolProperties is a mutable bean, so the transport must keep its own
      // check for a value edited after boot.
      //
      // The value is deliberately not echoed. `https://user:key@host/v1` is
      // a legal thing to configure, which is why OpenAiTransport strips
      // userinfo before printing one; not printing it at all is the same
      // guarantee without a second copy of that logic to keep correct.
      throw new IllegalStateException(
          "pool '"
              + pool.getName()
              + "' has a base-url that is not a URL this client"
              + " can call. It almost certainly has no scheme: write"
              + " http://host:port/v1 rather than host:port/v1. Left to be found at"
              + " the first request, this costs a corpus — the write path swallows"
              + " embedding failures, so every memory would be stored unembedded"
              + " and recall nothing, with nothing logged above it to say so");
    }
    if (HttpUrl.parse(pool.getBaseUrl() + "/embeddings") == null
        || !pool.getBaseUrl().equals(pool.getBaseUrl().trim())
        || pool.getBaseUrl().indexOf('#') >= 0
        || pool.getBaseUrl().indexOf('?') >= 0) {
      // The boot check above validates a different string than the
      // transport ever calls. OpenAiTransport.url does HttpUrl.parse(baseUrl
      // + path), and there are base URLs that parse alone and then mean
      // something else once a path is appended:
      //
      //   "http://host:1234/v1 "  (trailing space) parses fine, and POSTs
      //                           to /v1%20/embeddings — a 404.
      //   "http://host:1234/v1#x" parses fine, and the fragment swallows
      //                           the appended path entirely — a 404.
      //   "http://host:1234/v1?k" the query does the same.
      //
      // Every one of those is a 404 on the write path, which Archive.embed
      // swallows by design so the memory survives — so the server boots,
      // accepts every write, embeds none, recalls nothing, and logs a
      // warning per write that reads as an endpoint being down. That is
      // precisely the outcome the base-url check exists to prevent,
      // arriving through the door it did not cover.
      //
      // Checked by parsing what the transport will parse, plus explicit
      // refusals for the two shapes that parse either way. The value is
      // not echoed, for the reason the scheme check gives.
      throw new IllegalStateException(
          "pool '"
              + pool.getName()
              + "' has a base-url that is not usable as a prefix"
              + " for a request path. It parses on its own, but the endpoint paths"
              + " are appended to it, so trailing whitespace, a '#' fragment or a"
              + " '?' query silently redirects or discards them and every call"
              + " 404s. Write it as a bare root: http://host:port/v1");
    }
    for (String model : pool.getModels()) {
      // The twin of the blank-class check below, and it was missing while
      // that one was present: `models: ["", "nomic-embed-text"]` bound
      // cleanly and left the pool advertising a name no endpoint answers
      // to. Nothing routes to it — LlmPool.resolve matches a specifier
      // exactly — so it is dead weight that reads as a declared model.
      // (A null entry cannot reach here: List.copyOf rejects it in the
      // getter, and the wrapper around this method is what names the pool
      // when it does.)
      if (model.isBlank()) {
        throw new IllegalStateException(
            "pool '"
                + pool.getName()
                + "' declares a blank entry in models; a pool"
                + " serves exactly the names it lists, so a blank one is a name no"
                + " request could ever resolve to");
      }
    }
    if (!pool.getClasses().isEmpty()) {
      // Refused, not ignored, and the message is the whole reason
      // PoolProperties still carries the field — see its javadoc. An
      // operator who upgrades without moving the key would otherwise get a
      // server whose classes resolve to nothing and whose configuration
      // still reads correctly.
      throw new IllegalStateException(
          "pool '"
              + pool.getName()
              + "' declares classes "
              + pool.getClasses().keySet()
              + "; that key is no longer per pool. Move it to plowshare.llm.classes,"
              + " one map for the server, and leave this pool declaring only the"
              + " models it serves. Per pool, two pools could map one class to two"
              + " different models and the dispatcher would choose between them on"
              + " queue depth — the same request answered by a different model"
              + " depending on which box was busier");
    }
    for (String seeing : pool.getVision()) {
      // REFUSED AND NOT IGNORED, on the models check above's reasoning one
      // step further. A blank or misspelt entry here binds cleanly and
      // declares a capability for a model the pool does not serve, so
      // `LlmDispatcher.requireSees` goes on refusing every agent that
      // needs vision -- while the operator is looking at a configuration
      // that plainly grants it. The typo and the refusal are in two
      // different files and nothing connects them.
      if (!pool.getModels().contains(seeing)) {
        throw new IllegalStateException(
            "pool '"
                + pool.getName()
                + "' declares vision for '"
                + seeing
                + "', which is not one of the models it serves "
                + pool.getModels()
                + ". Vision is declared per pool against a"
                + " WIRE MODEL NAME -- not a class, which is a name for a"
                + " routing decision rather than a fact about a model");
      }
    }
    if (pool.getModels().isEmpty()) {
      // Was "neither models nor classes". A pool's classes are gone, so
      // models alone is what it can be reached by: a pool with no model
      // serves nothing, and every class resolves through a model this pool
      // would have to list.
      throw new IllegalStateException(
          "pool '"
              + pool.getName()
              + "' declares no models, so no request could ever be"
              + " routed to it. A class in plowshare.llm.classes reaches a pool only"
              + " through a model the pool itself lists");
    }
    if (pool.getChat() < 1 || pool.getEmbedding() < 1) {
      // LlmPool.lane does Math.max(1, slots) as a last-resort floor, so a
      // zero here does not crash — it quietly serves traffic on one slot
      // that the operator believed they had switched off. A floor is not
      // validation, and the difference is only visible from here.
      throw new IllegalStateException(
          "pool '"
              + pool.getName()
              + "' asks for "
              + pool.getChat()
              + " chat and "
              + pool.getEmbedding()
              + " embedding slot(s); both must be at least 1."
              + " To stop routing to a host, remove it from plowshare.llm.pools"
              + " rather than sizing it to zero");
    }
    if (pool.getSwarm() < 0 || (pool.getSwarm() > 0 && pool.getSwarm() >= pool.getChat())) {
      // Below chat and not at it: the swarm scheduler admits up to this many calls here, and
      // equal would let swarm work take every slot, leaving none for anything unscheduled —
      // a person's own turn among them, though the slots above swarm are not reserved for
      // it; every unscheduled run shares them. Zero is the way to say none.
      throw new IllegalStateException(
          "pool '"
              + pool.getName()
              + "' asks for "
              + pool.getSwarm()
              + " swarm slot(s);"
              + " a pool's swarm slots must sit below its "
              + pool.getChat()
              + " chat slot(s), or be explicitly 0 for none");
    }
    if (pool.getSubmitTimeout() == null) {
      // A null default is a NullPointerException on the first request that
      // names no budget of its own — which is nearly all of them — raised
      // inside an executor and reported as a wrapped cause naming nothing.
      //
      // Not reachable from configuration, and kept anyway. Measured: an
      // empty `submit-timeout:` binds to the field's 30s default rather
      // than to null, because Spring's JavaBeanBinder skips the setter for
      // a value it resolved as null — so no YAML produces this. What does
      // is a PoolProperties built in code, which is how every test in this
      // slice and TransactionBoundaryTest build one, and LlmPool's
      // constructor javadoc names this class as the place that refuses it.
      // The reachable half of this key is the next check, which the plan's
      // draft did not have at all.
      throw new IllegalStateException(
          "pool '"
              + pool.getName()
              + "' has a null submit-timeout; it is the budget"
              + " every request that names none inherits");
    }
    if (pool.getSubmitTimeout().isNegative() || pool.getSubmitTimeout().isZero()) {
      // This one an operator can actually write, and it is the fault the
      // null check was aimed at: `submit-timeout: -1s` binds cleanly to
      // PT-1S, and LlmPool.submit then awaits its start latch for a
      // non-positive number of milliseconds, which returns false without
      // waiting. Every request is shed as saturated — on an *idle* pool
      // too, since a caller that waits zero milliseconds loses the race
      // with the executor thread it just handed the task to. So this is
      // not "never queue", which would be a defensible setting; it is a
      // host that reports itself busy while doing nothing, which is what
      // PoolProperties' own warning about a 45ms budget describes.
      //
      // "Nearly every" and not "every", because the race is winnable now
      // and then: measured over 200 calls to an idle one-slot pool, 197
      // were shed at -1s and 198 at 0s. The remainder is what makes this
      // worse than an outright refusal to serve, not better — a host that
      // fails 99% of the time looks intermittent rather than misconfigured.
      throw new IllegalStateException(
          "pool '"
              + pool.getName()
              + "' has a non-positive submit-timeout ("
              + pool.getSubmitTimeout()
              + "); it is the budget every request that"
              + " names none inherits, and a pool that waits no time for a slot"
              + " sheds nearly every request as saturated even while it is idle."
              + " To stop routing to a host, remove it from plowshare.llm.pools");
    }
    if (pool.getMaxStreamDuration() == null) {
      // Split from the non-positive case below rather than folded in with
      // it, because the folded message asserted "non-positive" about a
      // value that is absent, which is a different fault with a different
      // fix. Unreachable from YAML for the reason given on submit-timeout
      // above, and kept on the same terms.
      throw new IllegalStateException(
          "pool '"
              + pool.getName()
              + "' has a null max-stream-duration; it is the"
              + " total budget a streaming call is allowed, and a stream has no"
              + " other bound");
    }
    if (pool.getMaxStreamDuration().isNegative() || pool.getMaxStreamDuration().isZero()) {
      // Zero fails every stream on its first event, which reads as an
      // endpoint that refuses to answer rather than as a setting.
      throw new IllegalStateException(
          "pool '"
              + pool.getName()
              + "' has a non-positive max-stream-duration ("
              + pool.getMaxStreamDuration()
              + "); it is the total budget a"
              + " streaming call is allowed, and a stream has no other bound —"
              + " per-chunk inactivity does not stop an endpoint that keeps"
              + " sending");
    }
    // The three read timeouts and the two retry knobs. Everything above
    // this line was already checked and these were not, which is the whole
    // of why they are here: a configuration fault has to name the pool and
    // the property, and until now these three could only be reported by
    // OkHttp, in three words, about neither.
    requireReadTimeout(
        pool,
        "chat-timeout",
        pool.getChatTimeout(),
        "how long a blocking chat call waits for the model");
    requireReadTimeout(
        pool,
        "streaming-timeout",
        pool.getStreamingTimeout(),
        "how long a stream waits between chunks before it gives up");
    requireReadTimeout(
        pool,
        "embedding-timeout",
        pool.getEmbeddingTimeout(),
        "how long an embedding batch waits for the model");
    if (pool.getRetryMaxAttempts() < 1) {
      // The exact twin of the lane-sized-to-zero check above, and it was
      // missing while that one was present. OpenAiTransport.executeWithRetry
      // floors this with Math.max(1, ...) just as LlmPool.lane floors a
      // slot count, so nothing crashes: `retry-max-attempts: 0` boots and
      // makes one attempt. An operator who wrote 0 meant either "do not
      // call at all" or "do not retry", and got a third thing under a
      // number they cannot find anywhere. A floor is not validation.
      throw new IllegalStateException(
          "pool '"
              + pool.getName()
              + "' asks for "
              + pool.getRetryMaxAttempts()
              + " retry-max-attempts; it must be at least 1, which means one"
              + " attempt and no retry. To stop calling a host, remove it from"
              + " plowshare.llm.pools rather than sizing its attempts to zero");
    }
    if (pool.getRetryInitialBackoff() == null) {
      throw new IllegalStateException(
          "pool '"
              + pool.getName()
              + "' has a null retry-initial-backoff; it is the"
              + " pause before the second attempt, and executeWithRetry reads it"
              + " on every call");
    }
    if (pool.getRetryInitialBackoff().isNegative()) {
      // Negative only. Zero is a real setting here — retry without
      // pausing — and is also what the Math.max(0L, ...) at the call site
      // silently turns a negative into, which is the entire complaint: a
      // typo that produces working behaviour under a value nobody wrote.
      // Note the unit trap this shares with nothing else in the class:
      // this key is milliseconds and every other duration in the pool is
      // seconds, so a value moved here from another key is already a
      // thousandfold out before its sign is considered.
      throw new IllegalStateException(
          "pool '"
              + pool.getName()
              + "' has a negative retry-initial-backoff ("
              + pool.getRetryInitialBackoff()
              + "); write 0ms for no pause between"
              + " attempts. A bare number here is MILLISECONDS, unlike every other"
              + " duration on this pool");
    }
    // Models alone. This used to add the pool's own classes' values, which
    // was the same set said twice: a class named a wire model, and the pool
    // had to serve that model for anything to reach it. With the map moved
    // to plowshare.llm.classes there is nothing per-pool left to add, and a
    // context length is keyed by a wire model in any case — a class is a
    // name no endpoint answers to, so it cannot appear here.
    Set<String> served = new LinkedHashSet<>(pool.getModels());
    pool.getContextLengths()
        .forEach(
            (model, tokens) -> {
              // The twin of the blank-model check above and refused for the same
              // reason: a context length keyed by a name this pool does not serve
              // binds cleanly, reads as configured, and is consulted by nothing.
              // A pool serves exactly the names it lists, so the key is a typo or
              // a leftover from a model that has been swapped out — and its only
              // other symptom is a length that is silently never found.
              //
              // The message deliberately does not blame the binder. An earlier
              // draft told the operator the key had to be bracketed because a
              // wire model name contains dots; measured on Spring Boot 3.3.5,
              // both forms bind the key verbatim, so that would have sent
              // somebody to fix punctuation that was already correct. See
              // PoolProperties#contextLengths.
              if (!served.contains(model)) {
                throw new IllegalStateException(
                    "pool '"
                        + pool.getName()
                        + "' sets a context-length for '"
                        + model
                        + "', which it does not serve; it declares "
                        + served
                        + ". A pool serves exactly the names it lists, so nothing would"
                        + " ever read this key");
              }
              if (tokens <= 0) {
                // Not merely nonsensical: this is the number a prompt is built
                // against, and a non-positive one means either every history is
                // compacted or the arithmetic goes negative, depending on which
                // way the caller subtracts. Empty is a state the design handles;
                // zero is one nothing does.
                throw new IllegalStateException(
                    "pool '"
                        + pool.getName()
                        + "' sets a context-length of "
                        + tokens
                        + " for '"
                        + model
                        + "'; it is a count of tokens a prompt may"
                        + " occupy and must be positive. Remove the key to leave the"
                        + " length unknown, which is a state this system handles");
              }
            });
    pool.getMaxContextLengths()
        .forEach(
            (model, tokens) -> {
              if (!served.contains(model) || tokens <= 0) {
                throw new IllegalStateException(
                    "pool '"
                        + pool.getName()
                        + "' max-context-lengths['"
                        + model
                        + "'] must name a served model and a positive token count; got "
                        + tokens);
              }
            });
    pool.getCompactionThresholds()
        .forEach(
            (model, tokens) -> {
              // The twin of the context-length checks above, refused for the same
              // two reasons and worth stating separately only because the failure
              // is quieter: a threshold keyed by a name this pool does not serve
              // binds, reads as configured, and is consulted by nothing — so the
              // conversation goes on folding at the derived default and the
              // operator has no way to tell.
              if (!served.contains(model)) {
                throw new IllegalStateException(
                    "pool '"
                        + pool.getName()
                        + "' sets a compaction-threshold for '"
                        + model
                        + "', which it does not serve; it declares "
                        + served
                        + ". A pool serves exactly the names it lists, so nothing would"
                        + " ever read this key");
              }
              if (tokens <= 0) {
                // A threshold of zero folds a conversation at every turn it can
                // fold at, buying a summary of a summary for ever; a negative
                // one does the same and reads as a typo nobody would find.
                // Removing the key is how you ask for the default.
                throw new IllegalStateException(
                    "pool '"
                        + pool.getName()
                        + "' sets a compaction-threshold of "
                        + tokens
                        + " for '"
                        + model
                        + "'; it is the prompt size in tokens at"
                        + " which a conversation folds and must be positive. Remove the"
                        + " key to fold at a fraction of the model's context length,"
                        + " which is the default");
              }
            });
    pool.getCompactionNowThresholds()
        .forEach(
            (model, tokens) -> {
              // compaction-thresholds' two reasons, for the in-turn fold (spec
              // 2026-09-30-fold-at-60-and-80 §1).
              if (!served.contains(model)) {
                throw new IllegalStateException(
                    "pool '"
                        + pool.getName()
                        + "' sets a compaction-now-threshold for '"
                        + model
                        + "', which it does not serve; it declares "
                        + served
                        + ". A pool serves exactly the names it lists, so nothing would"
                        + " ever read this key");
              }
              if (tokens <= 0) {
                throw new IllegalStateException(
                    "pool '"
                        + pool.getName()
                        + "' sets a compaction-now-threshold of "
                        + tokens
                        + " for '"
                        + model
                        + "'; it is the prompt size in tokens"
                        + " at which a conversation folds inside a turn and must be"
                        + " positive. Remove the key to derive it from the model's"
                        + " context length, which is the default");
              }
            });
    requireFoldThresholdsInOrder(pool, served);
    pool.getHarnessProfiles()
        .forEach(
            (model, profile) -> {
              // context-lengths' reason: a key naming a model this pool does not
              // serve binds cleanly and is consulted by nothing.
              if (!served.contains(model)) {
                throw new IllegalStateException(
                    "pool '"
                        + pool.getName()
                        + "' sets a harness-profile for '"
                        + model
                        + "', which it does not serve; it declares "
                        + served);
              }
            });
  }

  /**
   * A pool's two fold thresholds for one model are the right way round and inside its window,
   * wherever this pool says enough to tell (spec 2026-09-30-fold-at-60-and-80 §1: neither may
   * exceed the ceiling; "now" must be above "due").
   *
   * <p>Only what this pool configures is checked, and only against itself: a window discovered at
   * run time, or a threshold another pool serving the same model configures, is not known here.
   * {@link FoldThresholds#of} is what answers those at run time, raising now to a due it would
   * otherwise fall under rather than overruling the operator's due.
   *
   * <p><b>Where the context length is configured the other half is derivable</b>, so a configured
   * due is checked against the derived now and a configured now against the derived due — a "now"
   * of 80 000 on a 131 072 window whose fold is due at 87 895 is two thresholds the wrong way round
   * even though only one of them was written down.
   */
  private static void requireFoldThresholdsInOrder(PoolProperties pool, Set<String> served) {
    Map<String, Integer> dues = pool.getCompactionThresholds();
    Map<String, Integer> nows = pool.getCompactionNowThresholds();
    Map<String, Integer> windows = pool.getContextLengths();
    for (String model : served) {
      Integer due = dues.get(model);
      Integer now = nows.get(model);
      Integer window = windows.get(model);
      Integer maximum = pool.getMaxContextLengths().get(model);
      if (maximum != null) {
        window = window == null ? maximum : Math.min(window, maximum);
      }
      if (window != null) {
        if (due != null && due > window) {
          throw new IllegalStateException(
              "pool '"
                  + pool.getName()
                  + "' sets a"
                  + " compaction-threshold of "
                  + due
                  + " for '"
                  + model
                  + "', above its"
                  + " context-length of "
                  + window
                  + "; a fold threshold exists to fold"
                  + " earlier than the wall, and the wall is not the operator's to raise");
        }
        if (now != null && now > window) {
          throw new IllegalStateException(
              "pool '"
                  + pool.getName()
                  + "' sets a"
                  + " compaction-now-threshold of "
                  + now
                  + " for '"
                  + model
                  + "', above"
                  + " its context-length of "
                  + window
                  + "; a fold threshold exists to"
                  + " fold earlier than the wall, and the wall is not the operator's to"
                  + " raise");
        }
      }
      FoldThresholds derived = window == null ? null : FoldThresholds.byWindow(window);
      Integer effectiveDue =
          due != null
              ? due
              : derived != null && derived.due().isPresent() ? derived.due().getAsInt() : null;
      Integer effectiveNow = now != null ? now : derived != null ? derived.now() : null;
      if ((due != null || now != null)
          && effectiveDue != null
          && effectiveNow != null
          && effectiveNow <= effectiveDue) {
        throw new IllegalStateException(
            "pool '"
                + pool.getName()
                + "' folds '"
                + model
                + "' inside a turn at "
                + effectiveNow
                + (now == null ? " (derived from its context-length)" : "")
                + ", which must be above the size at which a fold is due between turns, "
                + effectiveDue
                + (due == null
                    ? " (derived from its context-length)"
                    : " (its compaction-threshold)")
                + ". Set compaction-now-threshold above compaction-threshold, or remove"
                + " one to derive it");
      }
    }
  }

  /**
   * Every class names a model, every model a class names is served by some pool, and no class takes
   * a name a pool already uses for a model.
   *
   * <h2>The second half is the check the move buys</h2>
   *
   * <p>While {@code classes} was per pool, a class pointing at a model nobody served was simply a
   * pool that never resolved it: the boot succeeded, and the fault surfaced at the first request
   * that happened to name that class, as {@code UnknownSpecifierException} from {@code
   * LlmDispatcher.route}. It could not be checked earlier because it was not a statement about
   * anything — one pool's map said nothing about what another pool served. With one map it is a
   * statement about the whole server, so it can be settled before the server starts, and the
   * message can name every pool that was searched rather than leaving an operator to compare lists
   * by eye.
   *
   * <p><b>A refusal and not a warning</b>, unlike {@link #warnAboutUnknownContextLengths}. The
   * difference is what the server can still do: a missing context length costs compaction on one
   * model and every other request is served correctly, whereas a class nothing serves is an agent
   * that cannot run at all — {@code AgentsConfig} disables an agent whose model no pool serves, so
   * the report an operator would otherwise get is one quietly missing agent. This is the same
   * reasoning {@code requireEmbeddingModelIsServed} applies to the archive's own model.
   *
   * <p>Given the two checks {@code validate} has already run — every pool declares at least one
   * model, and no pool declares blank ones — "some pool lists this name" is exactly "some pool can
   * be routed to for this class".
   *
   * <h2>The third refusal: a class NAME that is also a model name</h2>
   *
   * <p>The two checks above are both about a class's <em>value</em>, and between them they left the
   * 2026-09-07 defect a second door. {@link io.aeyer.plowshare.server.llm.dispatch.LlmPool#resolve}
   * tries {@code models} before the class map, so a class whose <em>key</em> is a model name
   * resolves two ways at once across a fleet:
   *
   * <pre>{@code
   * plowshare.llm.classes:
   *   qwen3.5-9b: qwen3.8-27b        # aliasing a retired name onto its replacement
   * plowshare.llm.pools:
   *   - name: studio
   *     models: [qwen3.8-27b]        # resolve("qwen3.5-9b") -> class -> qwen3.8-27b
   *   - name: spark
   *     models: [qwen3.5-9b]         # resolve("qwen3.5-9b") -> model -> qwen3.5-9b
   * }</pre>
   *
   * <p>Both pools answer, with <b>different wire models</b>, and {@code LlmDispatcher.route} picks
   * between them on queue depth — which is the defect this whole change exists to close, reached
   * from the shape an operator would naturally reach for when a model is retired and renamed. The
   * knock-ons are worse than the routing itself: {@code LlmDispatcher.wireModelFor} returns the
   * first pool's answer and a sampling profile is chosen for a model the request may not go to, and
   * {@code contextLength} takes the minimum across two unrelated models' bounds.
   *
   * <p><b>This cannot be over-tight, and that is worth stating because the next reader will
   * ask.</b> Take any class {@code C} some pool lists as a model, naming model {@code M}. Either
   * {@code C == M}, or {@code C != M}. If {@code C == M} the entry is inert — every pool serving it
   * answers with the model and the class map is never consulted, so removing the key changes
   * nothing. If {@code C != M}, then either no pool serves {@code M}, which the check above already
   * refuses, or some pool does, and that pool answers {@code M} while the pool listing {@code C}
   * answers {@code C}: ambiguity. There is no third case, so there is no configuration this refusal
   * wrongly rejects — every one it stops is either dead or the bug.
   *
   * <h2>The fourth refusal: a name reserved for system indirection</h2>
   *
   * <p>{@code system} and {@code system.<type>} are not classes and were deliberately never added
   * to {@link #requireClassesResolvable this method's own map} — see {@link
   * io.aeyer.plowshare.server.llm.LlmProperties#systemSpecifier}'s javadoc for why a system binding
   * is not a capability class. But {@code LlmDispatcher.resolveSpecifier} intercepts every
   * specifier shaped like one of those two forms <em>before</em> a pool is ever consulted, which
   * means a class or a model actually named {@code system} — or {@code system.compaction}, or any
   * other {@code system.*} — could never be reached by that name no matter what {@link
   * io.aeyer.plowshare.server.llm.dispatch.LlmPool#resolve} would otherwise answer for it. A caller
   * writing {@code model: system} always means the binding, never the literal model, and there is
   * no configuration of {@code plowshare.llm.system} that changes that.
   *
   * <p>This is the third refusal's ambiguity shape wearing a different name. There a class name
   * collided with a model name and the two could resolve to different wire models depending on
   * load; here a class or model name collides with a name {@code LlmDispatcher} treats specially
   * before routing runs at all, so the collision is not "which pool answers" but "this name can
   * never be asked for directly" — silent at boot, since nothing here reads {@code
   * plowshare.llm.system} to know whether the indirection is even configured, and loud only the
   * first time an agent actually names the shadowed key and gets routed somewhere it never
   * expected.
   *
   * <p>Checked for every pool's {@code models} unconditionally, not only when {@code classes} is
   * non-empty: a model named {@code system} is unreachable whether or not any class exists to
   * collide with it, because {@code LlmDispatcher} intercepts the specifier before consulting
   * either map.
   *
   * @param declared the pools as bound, for the model names and for the list a refusal names.
   *     Passed rather than read again from {@code props} so that this reads the same list the pools
   *     were built from
   */
  private static void requireClassesResolvable(LlmProperties props, List<PoolProperties> declared) {
    for (PoolProperties pool : declared) {
      for (String model : pool.getModels()) {
        if (reservedForSystemIndirection(model)) {
          throw new IllegalStateException(
              "pool '"
                  + pool.getName()
                  + "' lists '"
                  + model
                  + "' in `models`,"
                  + " which is reserved. 'system' and every 'system.<type>' are"
                  + " intercepted by LlmDispatcher before any pool is asked, so a"
                  + " model of that name could never be reached by a caller"
                  + " naming it directly — the request would always be"
                  + " redirected through plowshare.llm.system instead, silently."
                  + " Rename the model");
        }
      }
    }
    Map<String, String> classes;
    try {
      classes = props.getClasses();
    } catch (NullPointerException nullValue) {
      // Map.copyOf in the getter rejects a null value, which is what
      // `fast: ~` binds to. Caught here because this is the one place that
      // can name the key rather than letting a binder stack trace carrying
      // neither the class nor the property reach the operator. The twin of
      // the catch around validate(...) above.
      throw new IllegalStateException(
          "plowshare.llm.classes has a class written with no value, as `fast: ~` binds."
              + " A class is a name no endpoint answers to, so it must say which"
              + " model fulfils it. Give the key a model or remove the key",
          nullValue);
    }
    if (classes.isEmpty()) {
      // Not refused. A deployment whose agents all name wire models
      // directly needs no classes, and there is nothing silent about it:
      // an agent naming a class nothing declares is refused by
      // requireServed with the class in the message.
      return;
    }
    Set<String> servedAnywhere = new LinkedHashSet<>();
    for (PoolProperties pool : declared) {
      servedAnywhere.addAll(pool.getModels());
    }
    classes.forEach(
        (className, wireModel) -> {
          // Ahead of every other check on this entry, on the same reasoning
          // as the listAsModel ordering below: a reserved name is dead
          // whichever way it is misconfigured, so there is no value of
          // wireModel that would make this refusal the wrong one to raise.
          if (reservedForSystemIndirection(className)) {
            throw new IllegalStateException(
                "plowshare.llm.classes declares the class '"
                    + className
                    + "', which is"
                    + " reserved. 'system' and every 'system.<type>' are intercepted"
                    + " by LlmDispatcher before either a class or a model is"
                    + " consulted, so this class could never be reached by that name —"
                    + " the request would always be redirected through"
                    + " plowshare.llm.system instead, silently. Rename the class");
          }
          if (wireModel.isBlank()) {
            throw new IllegalStateException(
                "plowshare.llm.classes maps the class '"
                    + className
                    + "' to no model;"
                    + " a class is a name no endpoint answers to, so it must say which"
                    + " model fulfils it");
          }
          // Before the served check and not after it, because a class name
          // that is also a model name is dead whether or not its value is
          // served, and "remove this key" is the fix in both cases. Reporting
          // the value first would send an operator to correct a model name on
          // an entry that could never have fired.
          List<String> listAsModel =
              declared.stream()
                  .filter(pool -> pool.getModels().contains(className))
                  .map(PoolProperties::getName)
                  .toList();
          if (!listAsModel.isEmpty()) {
            throw new IllegalStateException(
                "plowshare.llm.classes declares the class '"
                    + className
                    + "', which "
                    + listAsModel
                    + " already list in `models`. A model name wins"
                    + " over a class of the same name when a pool resolves a"
                    + " specifier, so this key is dead where those pools take the"
                    + " request — they answer '"
                    + className
                    + "' — and ambiguous"
                    + " wherever another pool serves '"
                    + wireModel
                    + "' and answers"
                    + " that instead: one specifier, two wire models, chosen by which"
                    + " box is busier. That is the fault plowshare.llm.classes exists"
                    + " to make impossible. Rename the class, or drop '"
                    + className
                    + "' from those pools' models");
          }
          if (!servedAnywhere.contains(wireModel)) {
            // Names the class, the model and the pools, because each of the
            // three is a different fix: a typo in the class's value, a model
            // the fleet no longer loads, or a pool whose `models` list was
            // edited without the class being followed.
            throw new IllegalStateException(
                "plowshare.llm.classes maps the class '"
                    + className
                    + "' to model '"
                    + wireModel
                    + "', which no pool serves. The pools searched were "
                    + declared.stream()
                        .map(pool -> pool.getName() + "=" + pool.getModels())
                        .collect(Collectors.joining(", "))
                    + ". A class reaches a pool only through a model that pool lists,"
                    + " so nothing would ever route for this class — and an agent"
                    + " naming it is disabled rather than refused, which is one"
                    + " quietly missing agent and no other report");
          }
        });
  }

  /**
   * Refuses a boot whose harness binding names something nothing serves.
   *
   * <p>The twin of {@link #requireClassesResolvable}, and separate from it for the reason {@code
   * LlmProperties.system}'s javadoc gives: a class is a statement about agent capability and a
   * system binding is a statement about which machine runs the harness. They are validated alike
   * and they are not the same key.
   *
   * <p>A specifier here may be a class name or a wire model name, because that is what {@code
   * LlmDispatcher} resolves. So this accepts either, and refuses only what is neither.
   *
   * <p>Reads {@code getSystemOverrides()} inside a {@code try} for the exact reason {@link
   * #requireClassesResolvable} reads {@code getClasses()} inside one: {@code Map.copyOf} in the
   * getter rejects a null value, which is what {@code plowshare.llm.system-overrides.compaction: ~}
   * binds to, and an operator writing that is the configuration this whole method exists to give a
   * legible refusal to — an uncaught {@code NullPointerException} naming neither the key nor the
   * property would be exactly the failure this feature was built to replace.
   */
  static void requireSystemResolvable(LlmProperties props, List<PoolProperties> declared) {
    Map<String, String> bindings = new LinkedHashMap<>();
    if (!props.getSystem().isBlank()) {
      bindings.put("plowshare.llm.system", props.getSystem());
    }
    Map<String, String> overrides;
    try {
      overrides = props.getSystemOverrides();
    } catch (NullPointerException nullValue) {
      // Map.copyOf in the getter rejects a null value, which is what
      // `compaction: ~` binds to. Caught here because this is the one
      // place that can name the key rather than letting a binder stack
      // trace carrying neither the override nor the property reach the
      // operator. The twin of the catch around getClasses() above.
      throw new IllegalStateException(
          "plowshare.llm.system-overrides has an override written with no value, as"
              + " `compaction: ~` binds. An override names which model handles one"
              + " type of harness work, so it must say which model fulfils it. Give"
              + " the key a model or remove the key",
          nullValue);
    }
    // No null survives the getter above — either it threw, or every value
    // it returned is non-null — so nothing here re-checks for one; a
    // `specifier != null` guard at this point could never fire.
    overrides.forEach(
        (type, specifier) -> {
          if (!specifier.isBlank()) {
            bindings.put("plowshare.llm.system-overrides." + type, specifier.strip());
          }
        });
    if (bindings.isEmpty()) {
      // Not refused, for requireClassesResolvable's reason: a deployment
      // that binds no harness work has bound none, and the failure of
      // something that wanted one is named at the call by the dispatcher.
      return;
    }
    Set<String> servedAnywhere = new LinkedHashSet<>();
    for (PoolProperties pool : declared) {
      servedAnywhere.addAll(pool.getModels());
    }
    Set<String> classNames = props.getClasses().keySet();
    bindings.forEach(
        (key, specifier) -> {
          if (classNames.contains(specifier) || servedAnywhere.contains(specifier)) {
            return;
          }
          throw new IllegalStateException(
              key
                  + " names '"
                  + specifier
                  + "', which is neither a class in"
                  + " plowshare.llm.classes "
                  + classNames
                  + " nor a model served by any"
                  + " pool "
                  + poolNames(declared)
                  + ". Harness work bound to a name"
                  + " nothing answers to fails at the call, one fold or one digest at a"
                  + " time, which is the failure this refusal exists to move to boot");
        });
  }

  private static List<String> poolNames(List<PoolProperties> declared) {
    return declared.stream().map(PoolProperties::getName).toList();
  }

  /**
   * Names the key that moved, rather than letting a binder failure carry neither the old key nor
   * the new one.
   *
   * <p>The precedent is the per-pool {@code classes} refusal in this file: "that key is no longer
   * per pool. Move it to plowshare.llm.classes". An operator who upgraded without moving the key
   * otherwise gets a server whose memory operations resolve to nothing and whose configuration
   * still looks correct.
   *
   * <p>Takes a raw {@code Map<String, Object>} rather than an {@code Environment} or {@code
   * MemoryProperties} so it can be called before either is trusted: {@code MemoryProperties} still
   * declares {@code model} as of this commit — it is Task 3 that removes the field — so binding
   * alone would not yet fail, and this refusal is what makes the retirement effective a commit
   * early. Public, and not package-private like {@link #requireSystemResolvable}, because its call
   * site is {@code DigestConfig} in a different package — see that class for why the check lives
   * there rather than here.
   */
  public static void requireMemoryModelRetired(Map<String, Object> environment) {
    if (!environment.containsKey("plowshare.memory.model")) {
      return;
    }
    throw new IllegalStateException(
        "plowshare.memory.model has been retired. Memory is harness work and is bound"
            + " with the rest of it: set plowshare.llm.system-overrides.memory, or"
            + " leave it unset to inherit plowshare.llm.system. See"
            + " implementation rationale §5.1 for"
            + " why memory's binding is worth setting separately");
  }

  /**
   * {@code system} itself, or {@code system.<type>} for some type — the two shapes {@code
   * LlmDispatcher.resolveSpecifier} intercepts ahead of routing.
   *
   * <p><b>Kept in sync with {@code LlmDispatcher}'s own private predicate of the same rule by hand,
   * not by sharing code.</b> The two live on opposite sides of a one-way package boundary — {@code
   * LlmConfig} depends on {@code dispatch}, never the reverse, which is the same reason {@link
   * io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher}'s three-argument constructor takes a
   * {@code UnaryOperator<String>} rather than an {@code LlmProperties} — and the rule is two
   * conditions on a literal string, which is a smaller and more stable surface than the interface a
   * shared helper would need to cross that boundary without inverting it.
   */
  private static boolean reservedForSystemIndirection(String name) {
    return name.equals("system") || name.startsWith("system.");
  }

  /**
   * One of the three durations handed straight to an OkHttp builder.
   *
   * <p><b>Zero is refused even though OkHttp accepts it, and that is the part worth arguing.</b> To
   * OkHttp a zero timeout means no timeout, which sounds like a coherent thing to configure. It is
   * not what an operator gets. The chat and embedding clients wrap their read timeout in {@code
   * OpenAiTransport.callCeiling}, which adds two ten-second connect-and-write budgets — so {@code
   * chat-timeout: 0} yields a call bounded at twenty seconds, shorter than the sixty it replaced,
   * under a number that appears nowhere. The streaming client wraps its read timeout the same way,
   * so {@code streaming-timeout: 0} is that surprise plus a second one: the call ceiling becomes
   * twenty seconds, which bounds the connect and the wait for response headers and then stops —
   * {@code okhttp-sse} switches the call timeout off once the response opens — while the read
   * timeout is gone altogether, so a stream that opens and then falls silent has no inactivity
   * bound at all and waits out {@code max-stream-duration} on a lane slot. (This paragraph used to
   * say the streaming client "takes the value raw as both its read timeout and its call timeout".
   * It did; it no longer does, and the ten-minute wait it warned about is now only the half that
   * follows the response opening.) Neither reading of the value is "no timeout".
   *
   * <p><b>Negative already failed the boot, and that is not the same as being checked.</b> {@code
   * OkHttpClient.Builder} rejects it, from inside {@code new OpenAiTransport(...)}, as {@code
   * IllegalArgumentException: timeout < 0} — measured, and the exact string. No pool, no property,
   * no unit: an operator with three timeout keys on each of however many hosts is told only that
   * one of them is negative. That is the shape of message this slice faults in other people's
   * exceptions throughout, and producing one of its own is worse than inheriting one.
   *
   * <p>The null branch is unreachable from any property source, on the terms {@code
   * submit-timeout}'s own check sets out: Spring's binder skips the setter for a value it resolves
   * as null, so an empty key leaves the field default. A {@code PoolProperties} built in code is
   * what carries a null, which is how the tests and {@code TransactionBoundaryTest} build one.
   *
   * @param what a fragment naming what the key controls, so each of the three messages says
   *     something a reader could act on rather than repeating the property name back at them
   */
  private static void requireReadTimeout(
      PoolProperties pool, String property, Duration value, String what) {
    if (value == null) {
      throw new IllegalStateException(
          "pool '"
              + pool.getName()
              + "' has a null "
              + property
              + "; it is "
              + what
              + ", and it is handed to OkHttp on every call");
    }
    if (value.isNegative() || value.isZero()) {
      throw new IllegalStateException(
          "pool '"
              + pool.getName()
              + "' has a non-positive "
              + property
              + " ("
              + value
              + "); it is "
              + what
              + ". Zero is not 'no timeout' here —"
              + " a blocking call would fall back to the transport's own"
              + " twenty-second ceiling and a stream would lose every bound but"
              + " max-stream-duration. Write the wait you want");
    }
  }

  private static void requireEmbeddingDim(LlmProperties props) {
    if (props.getEmbeddingDim() < 1) {
      // Not merely nonsensical: the mismatch message this produces asserts
      // "vector(0) in the schema", which is false, and it arrives as an
      // EmbeddingException — so ApiExceptionHandler appends "this will work
      // again once the endpoint is back" to a fault that no amount of
      // waiting fixes. Refusing it here is the only place the operator is
      // told the truth.
      throw new IllegalStateException(
          "plowshare.llm.embedding-dim is "
              + props.getEmbeddingDim()
              + "; it must be positive and must match the vector(n) width in"
              + " V1__memories.sql");
    }
  }

  /**
   * There has to be an input ceiling, and zero is not "no ceiling".
   *
   * <p>The input-side twin of {@link #requireEmbeddingDim}, and it is refused here rather than
   * defaulted for the reason that key is: {@code application.yml} owns the number and a Java
   * initializer would be a second spelling of it that nothing compares.
   *
   * <p>What a missing bound costs is the whole of what the bound is for. {@code nomic-embed-text}
   * is loaded at 2048 tokens and {@code implementation rationale} records that nobody knows whether
   * a longer input is "truncated or refused" — so with no ceiling, the good case is an error from
   * the endpoint and the bad case is a silently truncated input that produces a vector for text
   * that is not the text. The row is then perfect, the search misses it, and nothing anywhere goes
   * red. That is not a fault an operator can be expected to notice later, which is why it is
   * refused before the server starts.
   *
   * <p><b>In tokens, counted by the {@link Tokenizer} bean.</b> This key was {@code
   * embedding-max-input-bytes}, a byte bound that could not be wrong and was a quarter of the
   * window for English prose. The shipped tokenizer estimates, so the shipped ceiling sits below
   * the model's window to leave room for an estimate that comes in low; a real tokenizer behind the
   * same interface can take that margin back.
   *
   * <h2>KNOWN GAP: "no tokenizer emits more tokens than its input has bytes" is false for an image
   * </h2>
   *
   * <p>That sentence was this key's whole justification while it was a byte bound, and it is
   * <b>true of text</b> — an embedding input is text, and nothing sends an image to an embedding
   * endpoint. It is kept here because the same reasoning is load-bearing elsewhere and does
   * <b>not</b> survive images.
   *
   * <p>A 145-byte PNG is hundreds of tokens. A vision encoder turns a picture into a fixed number
   * of embeddings per tile regardless of how well the bytes compressed, so bytes are not an upper
   * bound on tokens and are not even loosely correlated with them. Every place in this server that
   * reasons about size from characters is therefore <b>wrong about a message carrying a picture, in
   * the direction that under-counts</b>:
   *
   * <ul>
   *   <li>{@code Compaction} decides a fold by comparing the previous turn's measured {@code
   *       prompt_tokens} against the context bound. The measured half is honest — it comes back
   *       from the endpoint — but anything that estimates ahead of a call is not;
   *   <li>{@code ChatMessage.content()} answers the words and deliberately omits an image, so a
   *       character count over a conversation counts a picture as nothing at all rather than as too
   *       little.
   * </ul>
   *
   * <p><b>Not fixed here and deliberately not papered over.</b> A fix means per-model tile
   * arithmetic — how a given vision tower splits an image, at what resolution — which is a fact
   * about a model that no {@code /v1} endpoint reports and that this server would have to hold a
   * table of. Recorded rather than guessed at, because a guessed multiplier would make the counts
   * look right while being wrong by a factor nobody could see. The mitigation that exists today is
   * the size cap: {@code plowshare.images.max-bytes} bounds how far wrong one message can be.
   */
  private static void requireEmbeddingMaxInputTokens(LlmProperties props) {
    if (props.getEmbeddingMaxInputTokens() < 1) {
      throw new IllegalStateException(
          "plowshare.llm.embedding-max-input-tokens is "
              + props.getEmbeddingMaxInputTokens()
              + "; it must be positive. It is the largest input, in tokens as"
              + " plowshare.llm.tokenizer counts them, that may be sent to the"
              + " embedding endpoint. Without it an oversized chunk is either"
              + " refused by the endpoint or silently truncated, and a truncated"
              + " input embeds text that is not the text");
    }
  }

  /**
   * A default context length has to be a length.
   *
   * <p>This key is the bottom tier of context-length resolution: it is what answers whenever nobody
   * configured a length for the model and no provider could discover one, and it is the reason
   * compaction can no longer be switched off by a node that will not say how big it is.
   *
   * <p>Which is exactly why zero cannot be allowed through. A bound of zero is below every prompt
   * any conversation can send, so every conversation folds on its first turn and goes on folding on
   * every turn after it — a summarising call bought per utterance, and a history summarised as fast
   * as it is written. Nothing downstream reports that: a fold that fires is not an error, and the
   * log line it writes looks like compaction working. This is the only place an operator is told.
   */
  private static void requireDefaultContextLength(LlmProperties props) {
    if (props.getDefaultContextLength() < 1) {
      throw new IllegalStateException(
          "plowshare.llm.default-context-length is "
              + props.getDefaultContextLength()
              + "; it must be positive. It is the context length assumed when"
              + " neither pools[...].context-lengths nor the node itself could"
              + " say, and a non-positive one folds every conversation on every"
              + " turn. application.yml ships 64000 and argues for it");
    }
  }

  /**
   * Blank is refused separately from unserved, and before any pool is built.
   *
   * <p>Not for the message alone. {@code requireServed("")} would refuse it too, as {@code
   * UnknownSpecifierException} — but that reads as "no pool serves ''", which sends an operator to
   * look at their pools when what is empty is the archive-wide setting. {@code
   * DispatchingEmbeddingClient} carries a runtime backstop for the same value and says why one is
   * not enough: past this check it is an {@code IllegalArgumentException} from {@code
   * EmbeddingRequest}, a misconfiguration wearing the type reserved for a caller's bad request,
   * which escapes {@code Archive.embed}'s narrow catch and reaches the agent as a {@code 500} on a
   * row that is already committed — and would destroy the memory outright on any write path whose
   * transaction enclosed the embed call, which the boundary today deliberately does not. {@code
   * DispatchingEmbeddingClient}'s javadoc carries the whole account and the condition that revives
   * the worse half.
   */
  private static void requireEmbeddingModelNamed(LlmProperties props) {
    String model = props.getEmbeddingModel();
    if (model == null || model.isBlank()) {
      throw new IllegalStateException(
          "plowshare.llm.embedding-model is blank; the dispatcher has no default and"
              + " will not pick one, so every memory would be stored unembedded");
    }
  }

  private static void requireEmbeddingModelIsServed(LlmDispatcher dispatcher, LlmProperties props) {
    try {
      dispatcher.requireServed(props.getEmbeddingModel());
    } catch (UnknownSpecifierException e) {
      throw new IllegalStateException(
          "plowshare.llm.embedding-model is '"
              + props.getEmbeddingModel()
              + "', which no pool declares. This is fatal at startup on purpose:"
              + " the write path swallows embedding failures so that a memory"
              + " written while the endpoint is down is still stored, so the"
              + " running server would accept every write, embed none of them,"
              + " recall nothing, and report nothing. "
              + e.getMessage(),
          e);
    }
  }
}
